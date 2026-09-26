package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.floor.BootSurfaceCompiler.Fragment;
import wi.unigrav.core.floor.BootSurfaceCompiler.Limits;
import wi.unigrav.core.floor.BootSurfaceCompiler.Qualification;
import wi.unigrav.core.floor.BootSurfaceCompiler.Reason;
import wi.unigrav.core.floor.BootSurfaceCompiler.SurfaceSet;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.StandingConstraints.BootTier;

class BootSurfaceCompilerTest {
    private static final Limits LIMITS = new Limits(512, 3072, 96, 65536, 2_000_000, 4096);
    private static final Qualification LOW = new Qualification("fixture:low", new BootTier(new FloorFieldProfile(32, 0.5, 2), 1.5, 5));
    private static final Qualification HIGH = new Qualification("fixture:high", new BootTier(new FloorFieldProfile(64, 0.5, 2), 1.5, 5));

    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @Test
    void isolatedBoxHasSixExactFiniteFacesAndRetainsTheObservation() {
        Primitive source = primitive(0, box(2, 3, 4, 5, 7, 9), true);
        SurfaceSet set = compile(List.of(source));
        assertEquals(6, set.fragments().size());
        assertSame(source, set.observations().getFirst());
        for (CardinalFrame normal : CardinalFrame.values()) {
            Fragment face = faces(set, normal).getFirst();
            int axis = axis(normal);
            double expected = coordinate(positive(normal) ? source.rawBounds().max() : source.rawBounds().min(), axis);
            assertEquals(expected, coordinate(face.geometry().bounds().min(), axis));
            assertEquals(expected, coordinate(face.geometry().bounds().max(), axis));
            assertEquals(List.of(source), face.contributors());
            assertEquals(LOW, face.qualification());
        }
        assertThrows(UnsupportedOperationException.class, () -> set.fragments().clear());
        assertThrows(UnsupportedOperationException.class, () -> set.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> set.fragments().getFirst().contributors().clear());
    }

    @Test
    void adjacentCubesLoseBothInternalFacesWithoutFillingTheirExternalSeams() {
        SurfaceSet set = compile(List.of(primitive(0, box(0, 0, 0, 1, 1, 1), true),
                primitive(1, box(1, 0, 0, 2, 1, 1), true)));
        assertEquals(10, set.fragments().size());
        assertTrue(faces(set, CardinalFrame.X_POS).stream().allMatch(f -> f.geometry().bounds().min().x() == 2));
        assertTrue(faces(set, CardinalFrame.X_NEG).stream().allMatch(f -> f.geometry().bounds().min().x() == 0));
        assertEquals(10, rasterized(set).size());
    }

    @Test
    void differentQualificationsStillShareOneSolidBoundaryButNotOneMaterial() {
        Primitive a = primitive(0, box(0, 0, 0, 1, 1, 1), true);
        Primitive b = primitive(1, box(1, 0, 0, 2, 1, 1), true);
        SurfaceSet set = success(BootSurfaceCompiler.compile(List.of(a, b), Map.of(a, LOW, b, HIGH), LIMITS));
        assertEquals(10, set.fragments().size());
        assertEquals(10, rasterized(set).size());
        assertTrue(faces(set, CardinalFrame.X_POS).stream().allMatch(f -> f.geometry().bounds().min().x() == 2));
        assertTrue(faces(set, CardinalFrame.X_NEG).stream().allMatch(f -> f.geometry().bounds().min().x() == 0));
        for (Fragment face : set.fragments()) {
            assertEquals(1, face.contributors().size());
            assertEquals(face.contributors().getFirst() == a ? LOW : HIGH, face.qualification());
        }
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void partialNonmagneticBurialKeepsOnlyTheExactExposedHalf(CardinalFrame frame) {
        Primitive source = primitive(0, frame.toWorld(box(0, 0, 0, 1, 1, 1)), true);
        Primitive blocker = primitive(1, frame.toWorld(box(0, 1, 0, 0.5, 2, 1)), false);
        SurfaceSet set = compile(List.of(source, blocker));
        List<Fragment> remaining = faces(set, frame);
        assertEquals(1, remaining.size());
        assertEquals(frame.toWorld(box(0.5, 1, 0, 1, 1, 1)), remaining.getFirst().geometry().bounds());
        assertEquals(List.of(source), remaining.getFirst().contributors());
        assertSame(blocker, set.observations().get(1));
    }

    @Test
    void containedMagneticBoxHasNoExteriorButCannotTurnNonmagneticContainerIntoASource() {
        Primitive inner = primitive(0, box(1, 1, 1, 2, 2, 2), true);
        Primitive outer = primitive(1, box(0, 0, 0, 3, 3, 3), false);
        SurfaceSet set = compile(List.of(inner, outer));
        assertTrue(set.fragments().isEmpty());
        assertEquals(2, set.observations().size());
    }

    @Test
    void identicalGeometryDeduplicatesOnlyWithinExplicitlyEqualQualification() {
        Primitive a = primitive(0, box(0, 0, 0, 1, 1, 1), true);
        Primitive b = primitive(1, a.rawBounds(), true);
        SurfaceSet same = compile(List.of(b, a));
        assertEquals(6, same.fragments().size());
        for (Fragment face : same.fragments()) assertEquals(List.of(a, b), face.contributors());
        SurfaceSet different = success(BootSurfaceCompiler.compile(List.of(a, b), Map.of(a, LOW, b, HIGH), LIMITS));
        assertEquals(12, different.fragments().size());
        for (Fragment face : different.fragments()) {
            assertEquals(1, face.contributors().size());
            assertEquals(face.contributors().getFirst() == a ? LOW : HIGH, face.qualification());
        }
        // Same rule ID does not erase a different effective profile.
        Qualification strongerSameRule = new Qualification(LOW.ruleId(), HIGH.effectiveTier());
        assertEquals(12, success(BootSurfaceCompiler.compile(List.of(a, b), Map.of(a, LOW, b, strongerSameRule), LIMITS)).fragments().size());
    }

    @Test
    void adjacentDoubleNormalIntervalsDoNotBecomeAnEpsilonContactBand() {
        Primitive source = primitive(0, box(0, 0, 0, 1, 1, 1), true);
        double next = Math.nextUp(1.0);
        Primitive touching = primitive(1, box(1, 0, 0, next, 1, 1), false);
        assertTrue(faces(compile(List.of(source, touching)), CardinalFrame.X_POS).isEmpty());
        Primitive separated = primitive(1, box(next, 0, 0, Math.nextUp(next), 1, 1), false);
        assertEquals(1, faces(compile(List.of(source, separated)), CardinalFrame.X_POS).size());
    }

    @Test
    void subnormalTangentIntervalsAreClassifiedWithoutAMidpointOrVolumeProduct() {
        double tiny = Double.MIN_VALUE;
        Primitive source = primitive(0, box(0, 0, 0, 1, tiny * 2, 1), true);
        Primitive blocker = primitive(1, box(1, 0, 0, 2, tiny, 1), false);
        Fragment visible = faces(compile(List.of(source, blocker)), CardinalFrame.X_POS).getFirst();
        assertEquals(box(1, tiny, 0, 1, tiny * 2, 1), visible.geometry().bounds());
    }

    @Test
    void exactPlanesAndLargeEndpointsAreNeverAveragedOrSnapped() {
        Primitive a = primitive(0, box(0, 0, 0, 1, 1, 1), true);
        Primitive b = primitive(1, box(0, 2, 0, Math.nextUp(1.0), 3, 1), true);
        assertEquals(List.of(1.0, Math.nextUp(1.0)), faces(compile(List.of(a, b)), CardinalFrame.X_POS).stream()
                .map(f -> f.geometry().bounds().min().x()).toList());
        Primitive huge = primitive(0, box(1e308, 1e308, 1e308, Math.nextUp(1e308), Math.nextUp(1e308), Math.nextUp(1e308)), true);
        assertEquals(6, compile(List.of(huge)).fragments().size());
    }

    @Test
    void completeIntegerUnionBoundaryMatchesIndependentVoxelAdjacency() {
        Random random = new Random(0xB007FACE);
        for (int sample = 0; sample < 32; sample++) {
            List<Primitive> sources = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int x = random.nextInt(4);
                int y = random.nextInt(4);
                int z = random.nextInt(4);
                sources.add(primitive(i, box(x, y, z, x + 1 + random.nextInt(2), y + 1 + random.nextInt(2), z + 1 + random.nextInt(2)), true));
            }
            Set<UnitFace> expected = new HashSet<>();
            for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
                if (!occupied(sources, x, y, z)) continue;
                for (CardinalFrame direction : CardinalFrame.values()) {
                    Vec3d n = direction.up();
                    if (occupied(sources, x + (int) n.x(), y + (int) n.y(), z + (int) n.z())) continue;
                    int[] p = {x, y, z};
                    int axis = axis(direction);
                    expected.add(new UnitFace(direction, p[axis] + (positive(direction) ? 1 : 0), p[(axis + 1) % 3], p[(axis + 2) % 3]));
                }
            }
            SurfaceSet set = compile(sources);
            assertEquals(expected, rasterized(set), "sample " + sample);
            Collections.reverse(sources);
            assertEquals(set.fragments(), compile(sources).fragments());
        }
    }

    @Test
    void eachBudgetIsExplicitAndExactLimitsDoNotReturnPartialSurfaces() {
        Primitive source = primitive(0, box(0, 0, 0, 1, 1, 1), true);
        List<Primitive> one = List.of(source);
        Map<Primitive, Qualification> material = Map.of(source, LOW);
        assertEquals(6, success(BootSurfaceCompiler.compile(one, material, new Limits(1, 6, 2, 6, 12, 6))).fragments().size());
        failure(one, material, new Limits(1, 5, 2, 6, 12, 6), Reason.RAW_FACE_LIMIT);
        failure(one, material, new Limits(1, 6, 2, 5, 12, 6), Reason.TILE_LIMIT);
        failure(one, material, new Limits(1, 6, 2, 6, 11, 6), Reason.COVERAGE_LIMIT);
        failure(one, material, new Limits(1, 6, 2, 6, 12, 5), Reason.FRAGMENT_LIMIT);
        Primitive second = primitive(1, box(1, 0, 0, 2, 1, 1), true);
        failure(List.of(source, second), Map.of(source, LOW, second, LOW), new Limits(1, 12, 2, 100, 1000, 20), Reason.PRIMITIVE_LIMIT);
        failure(List.of(source, second), Map.of(source, LOW, second, LOW), new Limits(2, 12, 2, 100, 1000, 20), Reason.CUT_LIMIT);
    }

    @Test
    void unknownQualificationAndDegenerateSolidsAreNotEmptySuccess() {
        Primitive source = primitive(0, box(0, 0, 0, 1, 1, 1), true);
        failure(List.of(source), Map.of(), LIMITS, Reason.MATERIAL_UNAVAILABLE);
        Primitive flat = primitive(1, box(0, 0, 0, 0, 1, 1), false);
        failure(List.of(source, flat), Map.of(source, LOW), LIMITS, Reason.DEGENERATE_COLLIDER);
        assertTrue(success(BootSurfaceCompiler.compile(List.of(), Map.of(), LIMITS)).fragments().isEmpty());
        Primitive nonmagnetic = primitive(2, source.rawBounds(), false);
        assertTrue(compile(List.of(nonmagnetic)).fragments().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> BootSurfaceCompiler.compile(List.of(nonmagnetic), Map.of(nonmagnetic, LOW), LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> BootSurfaceCompiler.compile(List.of(source, source), Map.of(source, LOW), LIMITS));
    }

    @Test
    void foreignQualificationCannotHideBehindAMissingRealQualification() {
        Primitive source = primitive(0, box(0, 0, 0, 1, 1, 1), true);
        Primitive foreign = primitive(1, box(0, 0, 0, 1, 1, 1), true);
        assertThrows(IllegalArgumentException.class,
                () -> BootSurfaceCompiler.compile(List.of(source), Map.of(foreign, LOW), LIMITS));
    }

    private static SurfaceSet compile(List<Primitive> primitives) {
        Map<Primitive, Qualification> qualifications = new HashMap<>();
        for (Primitive primitive : primitives) if (primitive.magnetic()) qualifications.put(primitive, LOW);
        return success(BootSurfaceCompiler.compile(primitives, qualifications, LIMITS));
    }

    private static SurfaceSet success(BootSurfaceCompiler.Compilation result) { return assertInstanceOf(SurfaceSet.class, result); }
    private static void failure(List<Primitive> sources, Map<Primitive, Qualification> material, Limits limits, Reason reason) {
        assertEquals(reason, assertInstanceOf(BootSurfaceCompiler.Unavailable.class,
                BootSurfaceCompiler.compile(sources, material, limits)).reason());
    }
    private static Primitive primitive(int identity, Aabb bounds, boolean magnetic) {
        return new Primitive(new BlockPos(identity, 64, 0), Blocks.IRON_BLOCK.getDefaultState(), magnetic, 19, 0, bounds);
    }
    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }
    private static List<Fragment> faces(SurfaceSet set, CardinalFrame normal) {
        return set.fragments().stream().filter(f -> f.geometry().outward() == normal).toList();
    }
    private static boolean occupied(List<Primitive> sources, int x, int y, int z) {
        return sources.stream().anyMatch(p -> p.rawBounds().min().x() <= x && p.rawBounds().max().x() >= x + 1
                && p.rawBounds().min().y() <= y && p.rawBounds().max().y() >= y + 1
                && p.rawBounds().min().z() <= z && p.rawBounds().max().z() >= z + 1);
    }
    private static Set<UnitFace> rasterized(SurfaceSet set) {
        Set<UnitFace> faces = new HashSet<>();
        for (Fragment fragment : set.fragments()) {
            CardinalFrame direction = fragment.geometry().outward();
            int axis = axis(direction);
            Aabb rectangle = fragment.geometry().bounds();
            int plane = (int) coordinate(rectangle.min(), axis);
            for (int u = (int) coordinate(rectangle.min(), (axis + 1) % 3); u < coordinate(rectangle.max(), (axis + 1) % 3); u++) {
                for (int v = (int) coordinate(rectangle.min(), (axis + 2) % 3); v < coordinate(rectangle.max(), (axis + 2) % 3); v++) {
                    assertTrue(faces.add(new UnitFace(direction, plane, u, v)), "overlapping compiled fragments");
                }
            }
        }
        return faces;
    }
    private static int axis(CardinalFrame frame) {
        Vec3d n = frame.up();
        return n.x() != 0 ? 0 : n.y() != 0 ? 1 : 2;
    }
    private static boolean positive(CardinalFrame frame) { return coordinate(frame.up(), axis(frame)) > 0; }
    private static double coordinate(Vec3d p, int axis) { return axis == 0 ? p.x() : axis == 1 ? p.y() : p.z(); }
    private record UnitFace(CardinalFrame direction, int plane, int u, int v) {
    }
}
