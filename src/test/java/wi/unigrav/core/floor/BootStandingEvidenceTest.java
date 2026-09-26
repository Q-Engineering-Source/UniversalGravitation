package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints;
import wi.unigrav.core.player.StandingConstraints.Availability;
import wi.unigrav.core.player.StandingConstraints.BootTier;
import wi.unigrav.core.player.StandingConstraints.Evidence;
import wi.unigrav.core.player.StandingConstraints.Surface;

/** Real capture through standing resolution, without claiming an installed live movement owner. */
class BootStandingEvidenceTest {
    private static final UUID PLAYER = new UUID(0, 111);
    private static final UUID SESSION = new UUID(0, 112);
    private static final BlockPos FLOOR = new BlockPos(8, 64, 8);
    private static final BootQueryFootprint.Limits FOOTPRINT_LIMITS = new BootQueryFootprint.Limits(1024, 4096, 64, 512);
    private static final BootSurfaceCompiler.Limits COMPILER_LIMITS =
            new BootSurfaceCompiler.Limits(512, 3072, 96, 65536, 2_000_000, 4096);
    private static final BootSurfaceQuery.Limits QUERY_LIMITS = new BootSurfaceQuery.Limits(4096, 65536);

    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @Test
    void qualifiedCaptureReachesResolverOnceWithoutManufacturingSupport(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, PLAYER)) {
            Captured captured = capture(world, 8.5, 65, 1, 7);
            Evidence joined = join(baseline(Optional.empty()), captured);
            var result = StandingConstraints.resolve(StandingConstraints.State.initial(),
                    new StandingConstraints.Request(100, false, joined));

            assertEquals(new Vec3(3, -32, 2), result.authoritativeForce().orElseThrow().combinedAcceleration());
            assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
            assertTrue(result.actualSupport().isEmpty());
            Surface target = result.bootMemory().orElseThrow().surface();
            assertTrue(target.identity().isPresent());
            assertNotEquals(new Surface(target.x(), target.y(), target.z(), target.outwardFace()), target);
            assertThrows(IllegalArgumentException.class, () -> join(joined, captured));
        }
    }

    @Test
    void realFiniteBlockEdgeProducesLateralPullButOnlyOneSelectedAttraction(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, PLAYER)) {
            Captured captured = capture(world, 9.5, 66, 1, 7);
            var result = StandingConstraints.resolve(StandingConstraints.State.initial(),
                    new StandingConstraints.Request(100, false, join(baseline(Optional.empty()), captured)));
            Vec3 pull = result.authoritativeForce().orElseThrow().bootAcceleration();

            assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
            assertTrue(pull.x() < 0 && pull.y() < 0);
            // A 0.6-wide player centred at x=9.5 has its nearest foot edge at x=9.2;
            // the cube ends at x=9, y=65 and the foot plane is y=66.
            assertEquals(0.2, pull.x() / pull.y(), 1.0e-6);
            assertTrue(Math.hypot(pull.x(), pull.y()) > 20 && Math.hypot(pull.x(), pull.y()) < 32);
            assertEquals(0.0, pull.z(), 0.0);
            assertTrue(result.actualSupport().isEmpty());
        }
    }

    @Test
    void sameSurfaceRetainsMemoryAcrossTickMoveAndRevisionButRuleChangeDoesNotConfirmAttachment(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, PLAYER)) {
            Captured first = capture(world, 8.5, 65, 1, 7);
            var acquired = StandingConstraints.resolve(StandingConstraints.State.initial(),
                    new StandingConstraints.Request(100, false, join(baseline(Optional.empty()), first)));
            var memory = acquired.bootMemory().orElseThrow();
            // Independent contact/placement-owner acceptance is simulated explicitly, not inferred from capture.
            var attached = StandingConstraints.State.attached(memory, CardinalFrame.Y_POS, memory.surface());

            world.world().setTotalWorldTime(1);
            // Keep the full native-float-width foot rectangle inside this finite patch.
            Captured moved = capture(world, 8.6, 65, 2, 7);
            var retained = StandingConstraints.resolve(attached, new StandingConstraints.Request(101, false,
                    join(baseline(Optional.of(memory.surface())), moved)));
            assertEquals(memory, retained.bootMemory().orElseThrow());
            assertEquals(StandingConstraints.BootPhase.ATTACHED, retained.nextState().bootPhase());

            Captured reconfigured = capture(world, 8.6, 65, 2, 8);
            var changed = StandingConstraints.resolve(attached, new StandingConstraints.Request(102, false,
                    join(baseline(Optional.of(memory.surface())), reconfigured)));
            assertNotEquals(memory.surface(), changed.bootMemory().orElseThrow().surface());
            assertNotEquals(StandingConstraints.BootPhase.ATTACHED, changed.nextState().bootPhase());
        }
    }

    @Test
    void ordinarySupportCannotConfirmQualifiedBootAttachment(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, PLAYER)) {
            Captured captured = capture(world, 8.5, 65, 1, 7);
            var acquired = StandingConstraints.resolve(StandingConstraints.State.initial(),
                    new StandingConstraints.Request(100, false, join(baseline(Optional.empty()), captured)));
            var memory = acquired.bootMemory().orElseThrow();
            var target = memory.surface();
            var ordinarySupport = new Surface(target.x(), target.y(), target.z(), target.outwardFace());
            var attached = StandingConstraints.State.attached(memory, CardinalFrame.Y_POS, target);

            // Ordinary support is separately sampled caller evidence, not boot attachment provenance.
            Evidence joined = join(baseline(Optional.of(ordinarySupport)), captured);
            var result = StandingConstraints.resolve(attached, new StandingConstraints.Request(101, false, joined));
            assertEquals(Availability.AVAILABLE, joined.availability());
            assertEquals(Optional.of(ordinarySupport), result.actualSupport());
            assertEquals(memory, result.bootMemory().orElseThrow());
            assertEquals(StandingConstraints.BootPhase.IN_RANGE_UNATTACHED, result.nextState().bootPhase());
        }
    }

    @Test
    void staleSourceOrTickBecomesUnknownNotAnEmptySuccessfulSample(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, PLAYER)) {
            Captured captured = capture(world, 8.5, 65, 1, 7);
            var advanced = captured.physical().successor(captured.physical().placement(), captured.physical().worldVelocity());
            assertEquals(Availability.UNAVAILABLE, BootStandingEvidence.withBootQuery(baseline(Optional.empty()),
                    captured.measurements(), advanced, captured.tick()).availability());
            assertEquals(Availability.UNAVAILABLE, BootStandingEvidence.withBootQuery(baseline(Optional.empty()),
                    captured.measurements(), captured.physical(), captured.tick() + 1).availability());
            var unbound = BootSurfaceQuery.evaluateGeometry(captured.measurements().source(),
                    captured.measurements().surfaces(), QUERY_LIMITS);
            assertThrows(IllegalArgumentException.class, () -> BootStandingEvidence.withBootQuery(baseline(Optional.empty()),
                    unbound, captured.physical(), captured.tick()));
        }
    }

    @Test
    void changedActualColliderCannotReuseBootEvidenceAtTheSameSemanticCenter(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, PLAYER)) {
            Captured captured = capture(world, 8.5, 65, 1, 7);
            var previous = captured.physical();
            var old = previous.placement();
            Aabb box = old.bounds();
            Aabb wider = new Aabb(new Vec3d(Math.nextDown(box.min().x()), box.min().y(), box.min().z()),
                    new Vec3d(Math.nextUp(box.max().x()), box.max().y(), box.max().z()));
            var changed = PlayerBodyPlacement.fromCardinalBounds(wider, old.transport(), old.attitude(),
                    (PlayerBodyShape.Cardinal) old.shape());
            assertEquals(old.center(), changed.center());
            var sameRevision = new PlayerPhysicalSnapshot(previous.scope(), previous.revision(), changed,
                    previous.worldVelocity());
            assertNotEquals(previous, sameRevision);
            assertEquals(Availability.UNAVAILABLE, BootStandingEvidence.withBootQuery(baseline(Optional.empty()),
                    captured.measurements(), sameRevision, captured.tick()).availability());
        }
    }

    private static Evidence baseline(Optional<Surface> support) {
        return new Evidence(Availability.AVAILABLE, new Vec3(3, 0, 0), new Vec3(0, 0, 2),
                Map.of(), List.of(), support);
    }

    private static Evidence join(Evidence baseline, Captured captured) {
        return BootStandingEvidence.withBootQuery(baseline, captured.measurements(), captured.physical(), captured.tick());
    }

    private static Captured capture(BootWorldFixture fixture, double x, double y, long revision, long rulesRevision) {
        var actor = fixture.actor();
        actor.setPosition(x, y, 8.5);
        var dimensions = new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight());
        var placement = new PlayerBodyPlacement(new Vec3d(x, y + actor.height / 2.0, 8.5),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        var physical = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, 3), revision,
                placement, new Vec3d(0, 0, 0));
        var footprint = BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(physical), 1.5, FOOTPRINT_LIMITS);
        fixture.install(footprint);
        fixture.put(FLOOR, Blocks.IRON_BLOCK.getDefaultState());
        var rules = new MagneticBlockReader.RuleSet(rulesRevision, List.of(new MagneticBlockReader.RuleBinding(
                "fixture:iron", new MagneticBlockRule("minecraft:iron_block", Map.of(), null))));
        var captured = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                BootWorldSurfaces.captureQualified(fixture.world(), actor, footprint, rules));
        var tier = new BootTier(new FloorFieldProfile(32, 0.5, 3), 1.5, 4);
        var query = BootSurfaceQuery.evaluateQualified(captured,
                Map.of("fixture:iron", tier), COMPILER_LIMITS, QUERY_LIMITS);
        var measured = assertInstanceOf(BootSurfaceQuery.Measurements.class, query, query::toString);
        return new Captured(measured, physical, fixture.world().getTotalWorldTime());
    }

    private record Captured(BootSurfaceQuery.Measurements measurements, PlayerPhysicalSnapshot physical, long tick) { }
}
