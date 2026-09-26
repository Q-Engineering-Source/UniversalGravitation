package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // A ray at x=.25 would be clear, but the chosen closest anchor is x=-.25.
        var failure = failure(cardinal(CardinalFrame.Y_POS), compile(1.5, floor, blocker), QUERY);
        assertEquals(Reason.VISIBILITY_UNRESOLVED, failure.reason());
        assertEquals(CardinalFrame.Y_POS, failure.fragment().orElseThrow().geometry().outward());
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
        assertEquals(Reason.VISIBILITY_BUDGET, failure(source, set, new BootSurfaceQuery.Limits(6, 1)).reason());
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
