package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootSurfaceCompiler.Qualification;
import wi.unigrav.core.floor.BootSurfaceQuery.Candidate;
import wi.unigrav.core.floor.BootSurfaceQuery.Measurements;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.BootSurfaceGeometry.FaceRectangle;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/** Stable patch-value tests through qualified capture; identity itself proves no owner or support state. */
class BootSurfaceIdentityTest {
    private static final UUID PLAYER = new UUID(0, 91);
    private static final UUID SESSION = new UUID(0, 92);
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
    void qualifiedSamePatchSurvivesSourceMoveAndPhysicalRevision(@TempDir Path dataDirectory) {
        BootSurfaceIdentity first;
        BootSurfaceIdentity moved;
        try (BootWorldFixture fixture = BootWorldFixture.open(dataDirectory, PLAYER)) {
            first = identity(fixture, 8.5, 1);
            fixture.world().setTotalWorldTime(37);
            // x=8.7 straddles the x=9 face with Minecraft's float-derived width.
            moved = identity(fixture, 8.6, 2);
        }

        assertEquals(first, moved);
        assertEquals(0, first.compareTo(moved));
        assertEquals(FLOOR, first.primaryContributorCell());
        assertEquals(1, first.contributors().size());
        var contributor = first.contributors().getFirst();
        assertEquals("minecraft:iron_block", contributor.blockRegistryId());
        assertTrue(contributor.listedState().isEmpty());
        assertEquals(new Aabb(new Vec3d(8, 64, 8), new Vec3d(9, 65, 9)), contributor.rawBounds());
        assertEquals(0, contributor.callbackOrdinal());
        assertEquals(new MagneticBlockReader.MaterialMatch("fixture:iron",
                MagneticBlockReader.EvidenceKind.STORED_STATE), contributor.materialMatch());
        assertThrows(UnsupportedOperationException.class, () -> first.contributors().clear());
    }

    @Test
    void valueComparisonIncludesScopeConfigTierStateAndFaceGeometry(@TempDir Path dataDirectory) {
        BootSurfaceIdentity identity = identity(dataDirectory, 8.5, 1);
        var contributor = identity.contributors().getFirst();

        var changedScope = new PlayerPhysicalSnapshot.Scope(identity.scope().playerId(), identity.scope().sessionId(),
                identity.scope().dimension(), identity.scope().worldEpoch() + 1);
        assertDifferent(identity, new BootSurfaceIdentity(changedScope, identity.rulesRevision(), identity.face(),
                identity.qualification(), identity.contributors()));
        assertDifferent(identity, new BootSurfaceIdentity(identity.scope(), identity.rulesRevision() + 1, identity.face(),
                identity.qualification(), identity.contributors()));

        Qualification stronger = new Qualification(identity.qualification().ruleId(),
                new BootTier(new FloorFieldProfile(33, 0.5, 3), 1.5, 4));
        assertDifferent(identity, new BootSurfaceIdentity(identity.scope(), identity.rulesRevision(), identity.face(),
                stronger, identity.contributors()));

        var changedState = new BootSurfaceIdentity.Contributor(contributor.cell(), contributor.blockRegistryId(),
                List.of(new BootSurfaceIdentity.StateProperty("fixture_state", "changed")), contributor.rawBounds(),
                contributor.callbackOrdinal(), contributor.materialMatch());
        assertDifferent(identity, new BootSurfaceIdentity(identity.scope(), identity.rulesRevision(), identity.face(),
                identity.qualification(), List.of(changedState)));

        Aabb original = identity.face().bounds();
        FaceRectangle changedFace = new FaceRectangle(new Aabb(original.min(),
                new Vec3d(Math.nextUp(original.max().x()), original.max().y(), original.max().z())),
                identity.face().outward());
        assertDifferent(identity, new BootSurfaceIdentity(identity.scope(), identity.rulesRevision(), changedFace,
                identity.qualification(), identity.contributors()));
    }

    @Test
    void constructorCanonicalizesContributorAndListedStateOrder() {
        var match = new MagneticBlockReader.MaterialMatch("fixture:iron", MagneticBlockReader.EvidenceKind.STORED_STATE);
        var first = new BootSurfaceIdentity.Contributor(new BlockPos(3, 64, 0), "minecraft:iron_block",
                List.of(new BootSurfaceIdentity.StateProperty("z", "1"), new BootSurfaceIdentity.StateProperty("a", "2")),
                box(3, 64, 0, 4, 65, 1), 1, match);
        var second = new BootSurfaceIdentity.Contributor(new BlockPos(2, 64, 0), "minecraft:iron_block", List.of(),
                box(2, 64, 0, 3, 65, 1), 0, match);
        var qualification = new Qualification("fixture:iron", tier());
        var scope = new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, 3);
        var face = new FaceRectangle(box(2, 65, 0, 4, 65, 1), CardinalFrame.Y_POS);

        BootSurfaceIdentity canonical = new BootSurfaceIdentity(scope, 9, face, qualification, List.of(first, second));
        BootSurfaceIdentity reordered = new BootSurfaceIdentity(scope, 9, face, qualification, List.of(second, first));
        assertEquals(canonical, reordered);
        assertEquals(0, canonical.compareTo(reordered));
        assertEquals(second, canonical.contributors().getFirst());
        assertEquals(List.of(new BootSurfaceIdentity.StateProperty("a", "2"),
                new BootSurfaceIdentity.StateProperty("z", "1")), first.listedState());
    }

    @Test
    void fromRejectsAValueEqualCandidateThatWasNotRetainedByQualifiedMeasurements(@TempDir Path dataDirectory) {
        Measured measured = measured(dataDirectory, 8.5, 1);
        Candidate copied = new Candidate(measured.candidate().fragment(), measured.candidate().proximity());

        assertThrows(IllegalArgumentException.class, () -> BootSurfaceIdentity.from(measured.measurements(), copied));
    }

    private static void assertDifferent(BootSurfaceIdentity left, BootSurfaceIdentity right) {
        assertNotEquals(left, right);
        assertNotEquals(0, left.compareTo(right));
    }

    private static BootSurfaceIdentity identity(Path dataDirectory, double actorX, long physicalRevision) {
        Measured measured = measured(dataDirectory, actorX, physicalRevision);
        return BootSurfaceIdentity.from(measured.measurements(), measured.candidate());
    }

    private static Measured measured(Path dataDirectory, double actorX, long physicalRevision) {
        try (BootWorldFixture fixture = BootWorldFixture.open(dataDirectory, PLAYER)) {
            return measured(fixture, actorX, physicalRevision);
        }
    }

    private static BootSurfaceIdentity identity(BootWorldFixture fixture, double actorX, long physicalRevision) {
        Measured measured = measured(fixture, actorX, physicalRevision);
        return BootSurfaceIdentity.from(measured.measurements(), measured.candidate());
    }

    private static Measured measured(BootWorldFixture fixture, double actorX, long physicalRevision) {
        EntityPlayer actor = fixture.actor();
        actor.setPosition(actorX, 65.0, 8.5);
        var dimensions = new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight());
        var placement = new PlayerBodyPlacement(new Vec3d(actor.posX, actor.posY + actor.height / 2.0, actor.posZ),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        var physical = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, actor.dimension, 3),
                physicalRevision, placement, new Vec3d(0, 0, 0));
        BootQueryFootprint footprint = BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(physical), 1.5,
                FOOTPRINT_LIMITS);
        fixture.install(footprint);
        fixture.put(FLOOR, Blocks.IRON_BLOCK.getDefaultState());
        var capture = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                BootWorldSurfaces.captureQualified(fixture.world(), actor, footprint, rules()));
        var query = BootSurfaceQuery.evaluateQualified(capture,
                Map.of("fixture:iron", tier()), COMPILER_LIMITS, QUERY_LIMITS);
        Measurements measurements = assertInstanceOf(Measurements.class, query, query::toString);
        Candidate candidate = measurements.candidates().stream().filter(value ->
                value.fragment().geometry().outward() == CardinalFrame.Y_POS).findFirst().orElseThrow();
        return new Measured(measurements, candidate);
    }

    private static MagneticBlockReader.RuleSet rules() {
        return new MagneticBlockReader.RuleSet(12, List.of(new MagneticBlockReader.RuleBinding("fixture:iron",
                new MagneticBlockRule("minecraft:iron_block", Map.of(), null))));
    }

    private static BootTier tier() {
        return new BootTier(new FloorFieldProfile(32, 0.5, 3), 1.5, 4);
    }

    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }

    private record Measured(Measurements measurements, Candidate candidate) {
    }
}
