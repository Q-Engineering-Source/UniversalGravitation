package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.floor.BootSurfaceCompiler.Qualification;
import wi.unigrav.core.floor.BootSurfaceCompiler.SurfaceSet;
import wi.unigrav.core.floor.BootSurfaceQuery.Measurements;
import wi.unigrav.core.floor.BootSurfaceQuery.Reason;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.BootTier;

class BootSurfaceQueryTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final PlayerBodyShape.Dimensions BODY = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);
    private static final BootSurfaceCompiler.Limits COMPILER = new BootSurfaceCompiler.Limits(512, 3072, 96, 65536, 2_000_000, 4096);
    private static final BootSurfaceQuery.Limits QUERY = new BootSurfaceQuery.Limits(4096, 65536);

    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void sixCardinalQueriesKeepExactReachAndOnlyRejectBehindFaces(CardinalFrame frame) {
        var source = cardinal(frame);
        var set = compile(1.5, primitive(0, frame.toWorld(box(-2, -2, -2, 2, -1.5, 2)), true));
        var result = success(source, set);
        assertSame(source, result.source());
        assertSame(set, result.surfaces());
        assertTrue(result.capture().isEmpty());
        assertEquals(1, result.candidates().size());
        var candidate = result.candidates().getFirst();
        assertEquals(frame, candidate.fragment().geometry().outward());
        assertEquals(new BootSurfaceGeometry.Gap(1.5, 1.5), candidate.proximity().gap());
        assertEquals(frame.up().multiply(-1), candidate.proximity().pullDirection());
        assertThrows(UnsupportedOperationException.class, () -> result.candidates().clear());

        var outside = compile(Math.nextDown(1.5), set.observations().getFirst());
        assertTrue(success(source, outside).candidates().isEmpty());
    }

    @Test
    void endpointOnlyContactsAndZeroLengthDoNotSelfOcclude() {
        var source = cardinal(CardinalFrame.Y_POS);
        var floor = primitive(0, box(-2, -2, -2, 2, -1, 2), true);
        // The selected foot anchor (-.25,0,-.25) touches this box only at t=0.
        var startContact = primitive(1, box(-0.3, 0, -0.3, -0.2, 0.1, -0.2), false);
        assertEquals(1, success(source, compile(1.5, floor, startContact)).candidates().size());
        var contact = success(source, compile(1.5, primitive(0, box(-2, -1, -2, 2, 0, 2), true)));
        assertEquals(1, contact.candidates().size());
        assertEquals(new BootSurfaceGeometry.Gap(0, 0), contact.candidates().getFirst().proximity().gap());
        assertEquals(source.physical(), contact.source().physical());
    }

    @Test
    void blockedNearestPathCannotPretendThatTheWholeFragmentIsAbsent() {
        var floor = primitive(0, box(-2, -2, -2, 2, -1, 2), true);
        var blocker = primitive(1, box(-0.3, -0.8, -0.3, -0.2, -0.2, -0.2), false);
        var source = cardinal(CardinalFrame.Y_POS);
        // A ray at x=.25 would be clear, but the chosen closest anchor is x=-.25.
        var failure = failure(source, compile(1.5, floor, blocker), QUERY);
        assertEquals(Reason.VISIBILITY_UNRESOLVED, failure.reason());
        assertEquals(CardinalFrame.Y_POS, failure.fragment().orElseThrow().geometry().outward());

        var diagnostic = failure.visibilityDiagnostic().orElseThrow();
        assertEquals(BootSurfaceGeometry.SourceKind.CARDINAL_FOOT_RECTANGLE, diagnostic.sourceKind());
        assertEquals(source.physical().scope(), diagnostic.sourceScope());
        assertEquals(source.physical().revision(), diagnostic.sourceRevision());
        assertEquals(source.anchorBounds(), diagnostic.sourceAnchorBounds());
        assertEquals(source.radius(), diagnostic.sourceRadius());
        assertEquals(new Vec3d(-0.25, 0, -0.25), diagnostic.sourceAnchor());
        assertEquals(new Vec3d(-0.25, -1, -0.25), diagnostic.surfaceAnchor());
        assertEquals(failure.fragment().orElseThrow().geometry(), diagnostic.fragmentGeometry());
        assertEquals(blocker.rawBounds(), diagnostic.offendingRawBounds());
        assertEquals(blocker.cell(), diagnostic.offendingCell());
        assertSame(blocker.storedState(), diagnostic.offendingState());
        assertEquals(1, diagnostic.observationOrdinal());
        assertEquals(2, diagnostic.observationCount());
        assertEquals(blocker.ordinal(), diagnostic.primitiveOrdinal());
        assertEquals(2, diagnostic.boxesTested());
        assertEquals(QUERY.maxVisibilityBoxTests(), diagnostic.visibilityBoxTestLimit());
        assertTrue(diagnostic.captureTick().isEmpty());
        assertTrue(diagnostic.rulesRevision().isEmpty());
        assertTrue(diagnostic.summary().length() < 2048, "diagnostic remains a fixed-size witness, not contributor data");

        var propagated = PlayerBootObservation.queryUnavailable(failure);
        assertEquals(PlayerBootObservation.Reason.QUERY_UNAVAILABLE, propagated.reason());
        assertEquals(java.util.Optional.of(diagnostic), propagated.visibilityDiagnostic());
        assertTrue(propagated.detail().contains(diagnostic.summary()),
                "the existing owner failure message must carry the same bounded visibility witness");
        assertTrue(propagated.cause().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void oneCapturedBoxCanProveWholeCardinalFaceOcclusionAcrossTranslatedAxisFrames(CardinalFrame frame) {
        var offset = new Vec3d(37.25, -19.5, 8.125);
        var source = cardinalAt(frame, offset, BODY);
        var target = primitive(0, framedAt(frame, box(-1, -2, -1, 1, -1, 1), offset), true);
        var blocker = primitive(1, framedAt(frame, box(-1, -0.8, -1, 1, -0.2, 1), offset), false);
        var surfaces = compile(1.5, target, blocker);

        var complete = assertInstanceOf(Measurements.class,
                BootSurfaceQuery.evaluateGeometry(source, surfaces, new BootSurfaceQuery.Limits(16, 2)),
                "the same raw blocker spans the full source/fragment hull in " + frame);
        assertTrue(complete.candidates().isEmpty(), "the only magnetic face is wholly hidden in " + frame);

        var budget = assertInstanceOf(BootSurfaceQuery.Unavailable.class,
                BootSurfaceQuery.evaluateGeometry(source, surfaces, new BootSurfaceQuery.Limits(16, 1)));
        assertEquals(Reason.VISIBILITY_BUDGET, budget.reason(),
                "the occlusion proof must use the already-counted blocker test in " + frame);
        assertTrue(budget.visibilityDiagnostic().isEmpty());
    }

    @Test
    void partialCrossSectionOneUlpShortAndSplitBlockersRemainUnresolved() {
        var standard = cardinal(CardinalFrame.Y_POS);

        // The chosen closest witness is inside the blocker, but the fragment extends farther in Z.
        var broadTarget = primitive(0, box(-1, -2, -1, 1, -1, 1), true);
        var shortFragmentBlocker = primitive(1, box(-1, -0.8, -0.75, 1, -0.2, 0.75), false);
        assertUnresolvedOnYFace(standard, compile(1.5, broadTarget, shortFragmentBlocker), shortFragmentBlocker);

        // The one-ULP uncovered upper Z strip is real: comparisons are exact and use no tolerance.
        var narrowTarget = primitive(0, box(-0.25, -2, -0.25, 0.25, -1, 0.25), true);
        var oneUlpShort = primitive(1,
                box(-0.25, -0.8, -0.25, 0.25, -0.2, Math.nextDown(0.25)), false);
        assertUnresolvedOnYFace(standard, compile(1.5, narrowTarget, oneUlpShort), oneUlpShort);

        // The source is wider than this fully covered target patch. One selected line is blocked,
        // but the same solid does not contain the source's transverse hull.
        var wideSource = cardinalAt(CardinalFrame.Y_POS, ZERO,
                new PlayerBodyShape.Dimensions(1.2, 2, 1.75));
        var smallTarget = primitive(0, box(-0.25, -2, -0.25, 0.25, -1, 0.25), true);
        var narrowBlocker = primitive(1, box(-0.25, -0.8, -0.25, 0.25, -0.2, 0.25), false);
        assertUnresolvedOnYFace(wideSource, compile(1.5, smallTarget, narrowBlocker), narrowBlocker);

        // Two adjacent half-boxes cover the union hull, but neither blocker alone certifies it.
        var leftHalf = primitive(1, box(-0.25, -0.8, -0.25, 0.25, -0.2, 0), false);
        var rightHalf = primitive(2, box(-0.25, -0.8, 0, 0.25, -0.2, 0.25), false);
        assertUnresolvedOnYFace(standard, compile(1.5, narrowTarget, leftHalf, rightHalf), leftHalf);
    }

    @Test
    void lowerBallAndEndpointOnlyContactKeepTheirExistingVisibilitySemantics() {
        var target = primitive(0, box(-2, -2, -2, 2, -1.75, 2), true);
        var broadBlocker = primitive(1, box(-2, -1.5, -2, 2, -1, 2), false);
        var freeResult = failure(sphere(), compile(2, target, broadBlocker), QUERY);
        assertEquals(Reason.VISIBILITY_UNRESOLVED, freeResult.reason(),
                "a positive-radius Free lower ball cannot use a point/rectangle occlusion certificate");
        assertEquals(broadBlocker.rawBounds(), freeResult.visibilityDiagnostic().orElseThrow().offendingRawBounds());

        var cardinal = cardinal(CardinalFrame.Y_POS);
        var floor = primitive(0, box(-2, -2, -2, 2, -1, 2), true);
        var endpointOnly = primitive(1, box(-2, 0, -2, 2, 0.5, 2), false);
        var clear = success(cardinal, compile(1.5, floor, endpointOnly));
        assertTrue(clear.candidates().stream().anyMatch(candidate ->
                candidate.fragment().geometry().outward() == CardinalFrame.Y_POS),
                "a blocker touching only the source endpoint does not occlude the open path");
    }

    private static void assertUnresolvedOnYFace(BootSurfaceGeometry.Source source, SurfaceSet surfaces,
                                               Primitive expectedBlocker) {
        var unavailable = failure(source, surfaces, QUERY);
        assertEquals(Reason.VISIBILITY_UNRESOLVED, unavailable.reason());
        assertEquals(CardinalFrame.Y_POS, unavailable.fragment().orElseThrow().geometry().outward());
        var diagnostic = unavailable.visibilityDiagnostic().orElseThrow();
        assertEquals(expectedBlocker.rawBounds(), diagnostic.offendingRawBounds());
    }

    @Test
    void qualifiedBlockedPathRetainsTheExistingCaptureAndRuleContext(@TempDir Path dataDirectory) {
        try (BootWorldFixture fixture = BootWorldFixture.open(dataDirectory, new UUID(0, 71))) {
            var actor = fixture.actor();
            actor.setPosition(8.5, 66.0, 8.5);
            var dimensions = new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight());
            var placement = new PlayerBodyPlacement(new Vec3d(actor.posX, actor.posY + actor.height / 2.0, actor.posZ),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
            var physical = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                    new UUID(0, 72), actor.dimension, 0), 7, placement, ZERO);
            var source = BootSurfaceGeometry.fromAccepted(physical);
            var footprint = BootQueryFootprint.plan(source, 2.0,
                    new BootQueryFootprint.Limits(1024, 4096, 64, 512));
            fixture.install(footprint);
            fixture.put(new BlockPos(10, 64, 8), Blocks.IRON_BLOCK.getDefaultState());
            var blockerCell = new BlockPos(9, 65, 8);
            fixture.put(blockerCell, Blocks.STONE.getDefaultState());

            var rules = new MagneticBlockReader.RuleSet(49,
                    List.of(new MagneticBlockReader.RuleBinding("fixture:iron",
                            new MagneticBlockRule("minecraft:iron_block", Map.of(), null))));
            var capture = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), actor, footprint, rules));
            var tier = new BootTier(new FloorFieldProfile(32, 0.5, 8), 2.0, 4);
            var result = assertInstanceOf(BootSurfaceQuery.Unavailable.class,
                    BootSurfaceQuery.evaluateQualified(capture, Map.of("fixture:iron", tier), COMPILER, QUERY));

            assertEquals(Reason.VISIBILITY_UNRESOLVED, result.reason());
            var diagnostic = result.visibilityDiagnostic().orElseThrow();
            assertEquals(source.kind(), diagnostic.sourceKind());
            assertEquals(source.physical().scope(), diagnostic.sourceScope());
            assertEquals(source.physical().revision(), diagnostic.sourceRevision());
            assertEquals(capture.snapshot().captureTick(), diagnostic.captureTick().orElseThrow());
            assertEquals(capture.rulesRevision(), diagnostic.rulesRevision().orElseThrow());
            assertEquals(blockerCell, diagnostic.offendingCell());
            var blocker = capture.snapshot().primitives().stream().filter(primitive -> primitive.cell().equals(blockerCell))
                    .findFirst().orElseThrow();
            assertSame(blocker.storedState(), diagnostic.offendingState());
            assertEquals(capture.snapshot().primitives().indexOf(blocker), diagnostic.observationOrdinal());
        }
    }

    @Test
    void closedBoxSingletonTangencyInsideTheOpenPathIsUnresolved() {
        var target = primitive(0, box(1.25, -2, -1, 2.25, -1, 1), true);
        var tangent = primitive(1, box(0.75, -0.5, -0.5, 1, -0.25, 0), false);
        var clear = success(cardinal(CardinalFrame.Y_POS), compile(2, target));
        assertTrue(clear.candidates().stream().anyMatch(c -> c.fragment().geometry().outward() == CardinalFrame.Y_POS));
        // (.25,0,-.25)->(1.25,-1,-.25) touches exactly (.75,-.5,-.25).
        assertEquals(Reason.VISIBILITY_UNRESOLVED,
                failure(cardinal(CardinalFrame.Y_POS), compile(2, target, tangent), QUERY).reason());
    }

    @Test
    void coplanarOpenPathContactIsNotSilentlyTreatedAsAnEndpoint() {
        var target = primitive(0, box(-2, -2, -2, 2, -1, 2), true);
        var tangent = primitive(1, box(-0.25, -0.8, -0.5, 0, -0.2, 0), false);
        assertEquals(Reason.VISIBILITY_UNRESOLVED,
                failure(cardinal(CardinalFrame.Y_POS), compile(1.5, target, tangent), QUERY).reason());
    }

    @Test
    void lowerBallPrefixIsIgnoredThroughItsExactSurfaceButNotBeyondIt() {
        var source = sphere();
        var floor = primitive(0, box(-2, -2, -2, 2, -1.75, 2), true);
        // This is a sensor-visibility fixture, not proof that a real body fits these boxes.
        var prefix = primitive(1, box(-0.125, -0.25, -0.125, 0.125, -0.125, 0.125), false);
        var clear = success(source, compile(1.5, floor, prefix));
        assertEquals(1, clear.candidates().size());
        assertEquals(new BootSurfaceGeometry.Gap(1.5, 1.5), clear.candidates().getFirst().proximity().gap());
        assertTrue(clear.source().physical().placement().physicalFeet().isEmpty());
        var outside = primitive(1, box(-0.125, -Math.nextUp(0.25), -0.125, 0.125, -0.125, 0.125), false);
        assertEquals(Reason.VISIBILITY_UNRESOLVED, failure(source, compile(1.5, floor, outside), QUERY).reason());
    }

    @Test
    void sphereContactHasNoOpenPathAndDoesNotAcquireAStandingState() {
        var source = sphere();
        var result = success(source, compile(1.5, primitive(0, box(-2, -2, -2, 2, -0.25, 2), true)));
        assertEquals(1, result.candidates().size());
        assertEquals(new BootSurfaceGeometry.Gap(0, 0), result.candidates().getFirst().proximity().gap());
        assertInstanceOf(PlayerBodyShape.FreeCapsule.class, result.source().physical().placement().shape());
    }

    @Test
    void diagonalBallPrefixUsesExactSquaredRadiusRatherThanARoundedWitness() {
        var target = primitive(0, box(1, -2, -2, 2, -1, 2), true);
        var inside = primitive(1, box(0, -0.125, -0.125, 0.125, 0, 0.125), false);
        var outside = primitive(1, box(0, -0.1875, -0.125, 0.1875, 0, 0.125), false);
        // The centre-to-corner ray has length sqrt(2), so the physical source
        // parameter .25/sqrt(2) is irrational. Both bounds are exact binary fractions.
        assertEquals(2, success(sphere(), compile(1.5, target, inside)).candidates().size());
        assertEquals(Reason.VISIBILITY_UNRESOLVED, failure(sphere(), compile(1.5, target, outside), QUERY).reason());
    }

    @Test
    void unrepresentableDistancePreservesItsNumericCauseInsteadOfLookingEmpty() {
        var dimensions = new PlayerBodyShape.Dimensions(1e307, 2e307, 1e307);
        var source = source(new PlayerBodyPlacement(new Vec3d(-1e308, 0, 0), Rotation3d.identity(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions)));
        var target = primitive(0, box(9e307, -1e307, -1e307, 1e308, 1e307, 1e307), true);
        var result = failure(source, compile(Double.MAX_VALUE, target), QUERY);
        assertEquals(Reason.NUMERIC, result.reason());
        assertTrue(result.cause().isPresent());
        var propagated = PlayerBootObservation.queryUnavailable(result);
        assertSame(result.cause().orElseThrow(), propagated.cause().orElseThrow(),
                "query cause identity and its stack must reach the existing owner failure boundary");
        assertTrue(propagated.visibilityDiagnostic().isEmpty());
    }

    @Test
    void straddlingOneFaceDoesNotBecomeAnEmptyOrFiniteBodyOverlapVerdict() {
        var source = cardinal(CardinalFrame.Y_POS);
        var below = primitive(0, box(-1, -2, -2, 0, -1, 2), true);
        assertTrue(source.physical().placement().bounds().min().y() > below.rawBounds().max().y());
        var result = failure(source, compile(1.5, below), QUERY);
        assertEquals(Reason.SIDE_UNRESOLVED, result.reason());
        assertEquals(CardinalFrame.X_POS, result.fragment().orElseThrow().geometry().outward());
    }

    @Test
    void noSurfacesAndExactExcludedRangesAreCompleteGeometryEmptyResults() {
        var source = cardinal(CardinalFrame.Y_POS);
        assertTrue(success(source, compile(1.5)).candidates().isEmpty());
        assertTrue(success(source, compile(1.5, primitive(0, box(-2, -4, -2, 2, -3, 2), true))).candidates().isEmpty());
        assertTrue(success(source, compile(1.5, primitive(0, box(-2, -2, -2, 2, -1, 2), false))).candidates().isEmpty());
    }

    @Test
    void exactBudgetsPassButExhaustionNeverLeaksPartialCandidates() {
        var source = cardinal(CardinalFrame.Y_POS);
        var set = compile(1.5, primitive(0, box(-2, -2, -2, 2, -1, 2), true),
                primitive(1, box(9, 9, 9, 10, 10, 10), false));
        assertEquals(1, assertInstanceOf(Measurements.class,
                BootSurfaceQuery.evaluateGeometry(source, set, new BootSurfaceQuery.Limits(6, 2))).candidates().size());
        assertEquals(Reason.FRAGMENT_LIMIT, failure(source, set, new BootSurfaceQuery.Limits(5, 2)).reason());
        var visibilityBudget = failure(source, set, new BootSurfaceQuery.Limits(6, 1));
        assertEquals(Reason.VISIBILITY_BUDGET, visibilityBudget.reason());
        assertTrue(visibilityBudget.visibilityDiagnostic().isEmpty(),
                "budget exhaustion is not a visibility blocker diagnostic and remains an explicit failure");
        assertThrows(IllegalArgumentException.class, () -> new BootSurfaceQuery.Limits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BootSurfaceQuery.Limits(1, 0));
    }

    @Test
    void finiteEdgeWitnessPreservesItsTangentialPull() {
        var source = cardinal(CardinalFrame.Y_POS);
        var result = success(source, compile(5, primitive(0, box(3.25, -5, -2, 4.25, -4, 2), true)));
        var top = result.candidates().stream().filter(c -> c.fragment().geometry().outward() == CardinalFrame.Y_POS)
                .findFirst().orElseThrow().proximity();
        assertEquals(new BootSurfaceGeometry.Gap(5, 5), top.gap());
        assertEquals(0.6, top.pullDirection().x(), 1e-15);
        assertEquals(-0.8, top.pullDirection().y(), 1e-15);
        assertEquals(0, top.pullDirection().z(), 0.0);
    }

    private static Measurements success(BootSurfaceGeometry.Source source, SurfaceSet set) {
        return assertInstanceOf(Measurements.class, BootSurfaceQuery.evaluateGeometry(source, set, QUERY));
    }

    private static BootSurfaceQuery.Unavailable failure(BootSurfaceGeometry.Source source, SurfaceSet set,
                                                       BootSurfaceQuery.Limits limits) {
        return assertInstanceOf(BootSurfaceQuery.Unavailable.class, BootSurfaceQuery.evaluateGeometry(source, set, limits));
    }

    private static SurfaceSet compile(double reach, Primitive... primitives) {
        Map<Primitive, Qualification> qualifications = new HashMap<>();
        for (Primitive primitive : primitives) if (primitive.magnetic()) {
            qualifications.put(primitive, new Qualification("fixture:boot", new BootTier(new FloorFieldProfile(32, 0.5, 8), reach, 4)));
        }
        return assertInstanceOf(SurfaceSet.class, BootSurfaceCompiler.compile(List.of(primitives), qualifications, COMPILER));
    }

    private static BootSurfaceGeometry.Source cardinal(CardinalFrame frame) {
        return source(new PlayerBodyPlacement(frame.up(), frame.attitude(), new PlayerBodyShape.Cardinal(frame, BODY)));
    }

    private static BootSurfaceGeometry.Source cardinalAt(CardinalFrame frame, Vec3d foot,
                                                          PlayerBodyShape.Dimensions dimensions) {
        return source(new PlayerBodyPlacement(foot.add(frame.up()), frame.attitude(),
                new PlayerBodyShape.Cardinal(frame, dimensions)));
    }

    private static Aabb framedAt(CardinalFrame frame, Aabb local, Vec3d offset) {
        Aabb world = frame.toWorld(local);
        return new Aabb(world.min().add(offset), world.max().add(offset));
    }

    private static BootSurfaceGeometry.Source sphere() {
        return source(new PlayerBodyPlacement(ZERO, Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.5, 0.5, 0.4))));
    }

    private static BootSurfaceGeometry.Source source(PlayerBodyPlacement placement) {
        return BootSurfaceGeometry.fromAccepted(new PlayerPhysicalSnapshot(
                new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 0, 3), 4, placement, ZERO));
    }

    private static Primitive primitive(int ordinal, Aabb box, boolean magnetic) {
        return new Primitive(new BlockPos(ordinal, 64, 0), Blocks.IRON_BLOCK.getDefaultState(), magnetic, 19, 0, box);
    }

    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }
}
