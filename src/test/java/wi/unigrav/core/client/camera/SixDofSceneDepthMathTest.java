package wi.unigrav.core.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;

/** Pure CPU checks of the numerical contract; these do not exercise an OpenGL driver. */
class SixDofSceneDepthMathTest {
    @Test
    void projectsAndUnprojectsWithCapturedColumnMajorMatrices() {
        SixDofSceneDepthMath.Frame frame = frame(new Vec3(0.0D, 0.0D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(frame);
        SixDofSceneDepthMath.Projection projection = SixDofSceneDepthMath.project(frame, new Vec3(0.75D, -0.5D, 0.0D));
        assertNotNull(projection);
        Vec3 restored = SixDofSceneDepthMath.unproject(frame, projection.x(), projection.y(), projection.depth());
        assertNotNull(restored);
        assertEquals(0.75D, restored.x(), 1.0E-6D);
        assertEquals(-0.5D, restored.y(), 1.0E-6D);
        assertEquals(0.0D, restored.z(), 1.0E-6D);
        float[] inverse = frame.inverseClip();
        inverse[0] = 0.0F;
        assertTrue(frame.inverseClip()[0] != 0.0F, "Frame never exposes mutable matrix state");
    }

    @Test
    void preparesStableTangentsForEveryAxisNormal() {
        Vec3[] normals = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0),
                new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
        for (Vec3 normal : normals) {
            SixDofSceneDepthMath.Frame frame = frame(Vec3.ZERO, normal);
            assertNotNull(frame);
            assertEquals(1.0D, frame.normal().norm(), 1.0E-12D);
            assertEquals(0.0D, frame.normal().dot(frame.tangent0()), 1.0E-12D);
            assertEquals(0.0D, frame.normal().dot(frame.tangent1()), 1.0E-12D);
            assertEquals(0.0D, frame.tangent0().dot(frame.tangent1()), 1.0E-12D);
        }
    }

    @Test
    void preservesLargeWorldPrecisionByOperatingOnRelativeCoordinates() {
        Vec3 viewerFeet = new Vec3(29_999_999.75D, -12_345.0D, -29_999_999.5D);
        Vec3 hitWorld = new Vec3(30_000_000.0D, -12_344.5D, -29_999_999.5D);
        Vec3 relative = hitWorld.sub(viewerFeet);
        SixDofSceneDepthMath.Frame frame = frame(relative, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(frame);
        assertEquals(relative.x(), frame.hitRelative().x(), 0.0D);
        assertEquals(relative.y(), frame.hitRelative().y(), 0.0D);
        assertEquals(relative.z(), frame.hitRelative().z(), 0.0D);
    }

    @Test
    void supportsReversedWindowDepthOnlyWithAnEndpointClearCode() {
        SixDofSceneDepthMath.Frame reversed = SixDofSceneDepthMath.prepare(modelView(), projection(), 100, 100,
                1.0D, 0.0D, 0.0D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(reversed);
        double reversedDepth = SixDofSceneDepthMath.project(reversed, reversed.hitRelative()).depth();
        assertTrue(reversedDepth > 0.0D && reversedDepth < 1.0D);
        assertNull(SixDofSceneDepthMath.prepare(modelView(), projection(), 100, 100,
                0.0D, 1.0D, 0.5D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D)));
    }

    @Test
    void d24PlanarSurfaceNeedsFourCoherentNeighboursAndRejectsForegroundOrWallDepth() {
        SixDofSceneDepthMath.Frame frame = frame(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(frame);
        double depth = d24(SixDofSceneDepthMath.project(frame, frame.hitRelative()).depth());
        assertTrue(SixDofSceneDepthMath.anchorMatches(frame, 49, 49, depth + 2.0D * SixDofSceneDepthMath.D24_STEP));
        assertFalse(SixDofSceneDepthMath.anchorMatches(frame, 0, 0, depth));
        assertEquals(1.0D, SixDofSceneDepthMath.surfaceMask(frame, 49, 49, depth, depth, depth, depth, depth),
                1.0E-6D, "near D24 plane locks the full precision alpha");
        assertEquals(0.0D, SixDofSceneDepthMath.surfaceMask(frame, 49, 49, depth, depth, depth - 0.1D, depth, depth));
        assertEquals(0.0D, SixDofSceneDepthMath.surfaceMask(frame, 49, 49, frame.clearDepth(), depth, depth, depth, depth));
    }

    @Test
    void threeByThreeAnchorProbeUsesPlaneAndExactHitTangentsRatherThanRawDepthEquality() {
        SixDofSceneDepthMath.Frame frame = frame(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(frame);
        double planeDepth = d24(SixDofSceneDepthMath.project(frame, frame.hitRelative()).depth());
        int baseX = (int) Math.floor(frame.anchorU());
        int baseY = (int) Math.floor(frame.anchorV());
        assertTrue(SixDofSceneDepthMath.anchorProbe(frame, (x, y) ->
                x == baseX + 1 && y == baseY ? planeDepth : frame.clearDepth()),
                "clear centre reconciles through one valid neighbouring pixel");
        assertFalse(SixDofSceneDepthMath.anchorProbe(frame, (x, y) -> frame.clearDepth()));
        double otherHeight = SixDofSceneDepthMath.project(frame, new Vec3(0.0D, 0.0D, -1.0D)).depth();
        assertFalse(SixDofSceneDepthMath.anchorProbe(frame, (x, y) -> otherHeight),
                "parallel surface at another height is not the selected plane");
        assertFalse(SixDofSceneDepthMath.anchorProbe(frame, (x, y) -> Double.NaN));
    }

    @Test
    void threeByThreeAnchorProbeSkipsOutOfViewportCandidatesAtTheViewportEdge() {
        SixDofSceneDepthMath.Frame edge = frame(new Vec3(-5.0D, 0.0D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(edge);
        assertEquals(0, (int) Math.floor(edge.anchorU()));
        double depth = d24(SixDofSceneDepthMath.project(edge, edge.hitRelative()).depth());
        assertTrue(SixDofSceneDepthMath.anchorProbe(edge, (x, y) -> {
            if (x < 0 || y < 0 || x >= edge.width() || y >= edge.height()) {
                throw new AssertionError("probe requested an out-of-viewport depth texel");
            }
            return x == 0 && y == (int) Math.floor(edge.anchorV()) ? depth : edge.clearDepth();
        }));
    }

    @Test
    void floatUploadAndD24PrecisionCannotBroadenAFarPlaneIntoSuccess() {
        SixDofSceneDepthMath.Frame floatFixture = SixDofSceneDepthMath.prepare(floatMatrix(modelView()),
                floatMatrix(projection()), 100, 100, 0.0D, 1.0D, 1.0D,
                Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(floatFixture, "float-upload residual is within the explicit frame gate");
        SixDofSceneDepthMath.Frame distant = SixDofSceneDepthMath.prepare(modelView(), farProjection(), 100, 100,
                0.0D, 1.0D, 1.0D, new Vec3(0.0D, 0.0D, -1_000.0D), new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(distant);
        double quantized = d24(SixDofSceneDepthMath.project(distant, distant.hitRelative()).depth());
        assertEquals(0.0D, SixDofSceneDepthMath.surfaceMask(distant, 49, 49,
                quantized, quantized, quantized, quantized, quantized),
                "D24 normal uncertainty beyond the hard maximum fails closed");
    }

    @Test
    void radialCoverageHasTheBoundedCircularFeatherAndIsBasisInvariant() {
        SixDofSceneDepthMath.Frame frame = frame(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(frame);
        assertEquals(1.0D, coverageAt(frame, 0.0D, 0.0D), 0.0D);
        assertEquals(1.0D, coverageAt(frame, 56.0D, 0.0D), 0.0D);
        assertEquals(0.5D, coverageAt(frame, 60.0D, 0.0D), 1.0E-12D);
        assertEquals(0.0D, coverageAt(frame, 64.0D, 0.0D), 0.0D);
        assertEquals(0.0D, coverageAt(frame, 65.0D, 0.0D), 0.0D);
        assertEquals(coverageAt(frame, 60.0D, 0.0D), coverageAt(frame, 48.0D, 36.0D), 1.0E-12D);
        assertEquals(0.0D, coverageAt(frame, 60.0D, 60.0D), 0.0D,
                "(60,60) was inside the old square but lies outside the 64 m circle");
        double previous = Double.POSITIVE_INFINITY;
        for (double radius = 0.0D; radius <= 72.0D; radius += 0.25D) {
            double current = coverageAt(frame, radius, 0.0D);
            assertTrue(Double.isFinite(current));
            assertTrue(current <= previous, "radial coverage must be monotonic");
            previous = current;
        }
        Vec3[] normals = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0),
                new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
        for (int index = 0; index < normals.length; index++) {
            SixDofSceneDepthMath.Frame rotatedAndTranslated = frame(new Vec3(index * 0.25D, -index * 0.5D, 0.0D), normals[index]);
            assertNotNull(rotatedAndTranslated);
            assertEquals(0.5D, coverageAt(rotatedAndTranslated, 48.0D, 36.0D), 1.0E-12D,
                    "translation and tangent-basis rotation do not change radial coverage");
        }
    }

    @Test
    void surfaceMaskCallsCircularCoverageAndRetainsThe328MetreAndPrecisionGates() {
        SixDofSceneDepthMath.Frame circular = SixDofSceneDepthMath.prepare(modelViewAt(100.0D), orthographicWide(), 200, 200,
                0.0D, 1.0D, 1.0D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(circular);
        double circularDepth = SixDofSceneDepthMath.project(circular, circular.hitRelative()).depth();
        assertEquals(0.0D, SixDofSceneDepthMath.surfaceMask(circular, 160, 160,
                circularDepth, circularDepth, circularDepth, circularDepth, circularDepth), 0.0D,
                "surfaceMask must use circular coverage; this point is square-inside but circle-outside");
        assertEquals(328.0D, SixDofSceneDepthMath.RECONSTRUCTION_GUARD, 0.0D);
        SixDofSceneDepthMath.Frame beyondGuard = SixDofSceneDepthMath.prepare(modelView(), farProjection(), 100, 100,
                0.0D, 1.0D, 1.0D, new Vec3(0.0D, 0.0D, -400.0D), new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(beyondGuard);
        double beyondDepth = d24(SixDofSceneDepthMath.project(beyondGuard, beyondGuard.hitRelative()).depth());
        assertEquals(0.0D, SixDofSceneDepthMath.surfaceMask(beyondGuard, 49, 49,
                beyondDepth, beyondDepth, beyondDepth, beyondDepth, beyondDepth),
                "a reconstructed point beyond the 328 m guard is rejected before any range coverage claim");
        SixDofSceneDepthMath.Frame precisionUnsafeWithinGuard = SixDofSceneDepthMath.prepare(modelViewAt(300.0D), tinyNearProjection(),
                100, 100, 0.0D, 1.0D, 1.0D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(precisionUnsafeWithinGuard);
        double depth = d24(SixDofSceneDepthMath.project(precisionUnsafeWithinGuard,
                precisionUnsafeWithinGuard.hitRelative()).depth());
        assertEquals(0.0D, SixDofSceneDepthMath.surfaceMask(precisionUnsafeWithinGuard, 49, 49,
                depth, depth, depth, depth, depth),
                "being inside the 328 m reconstruction guard cannot waive D24 precision rejection");
    }

    @Test
    void boundsAndFailureCasesFailClosed() {
        SixDofSceneDepthMath.Frame wide = SixDofSceneDepthMath.prepare(modelView(), orthographicWide(), 200, 200,
                0.0D, 1.0D, 1.0D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D));
        assertNotNull(wide);
        double depth = SixDofSceneDepthMath.project(wide, wide.hitRelative()).depth();
        assertEquals(0.0D, SixDofSceneDepthMath.surfaceMask(wide, 165, 100, depth, depth, depth, depth, depth));
        assertNull(SixDofSceneDepthMath.prepare(new double[16], projection(), 100, 100,
                0.0D, 1.0D, 1.0D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D)));
        assertNull(SixDofSceneDepthMath.prepare(modelView(), projection(), 0, 100,
                0.0D, 1.0D, 1.0D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D)));
        assertNull(SixDofSceneDepthMath.prepare(modelView(), projection(), 100, 100,
                0.0D, 1.0D, 1.0D, Vec3.ZERO, Vec3.ZERO));
        assertNull(SixDofSceneDepthMath.prepare(floatOverflowingInverseModelView(), projection(), 100, 100,
                0.0D, 1.0D, 1.0D, Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D)),
                "a finite double inverse that overflows GLSL float uniforms must fail closed");
    }

    private static SixDofSceneDepthMath.Frame frame(Vec3 hit, Vec3 normal) {
        return SixDofSceneDepthMath.prepare(modelView(), projection(), 100, 100,
                0.0D, 1.0D, 1.0D, hit, normal);
    }

    private static double coverageAt(SixDofSceneDepthMath.Frame frame, double tangent0, double tangent1) {
        return SixDofSceneDepthMath.coverageFade(frame, frame.hitRelative()
                .add(frame.tangent0().scale(tangent0)).add(frame.tangent1().scale(tangent1)));
    }

    /** Column-major view transform: eye at relative (0,0,5). */
    private static double[] modelView() {
        return modelViewAt(5.0D);
    }

    private static double[] modelViewAt(double eyeZ) {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -eyeZ, 1};
    }

    /** Column-major OpenGL perspective matrix, near=1 and far=100. */
    private static double[] projection() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -101.0D / 99.0D, -1,
                0, 0, -200.0D / 99.0D, 0};
    }

    /** Column-major wide orthographic projection, used to reach the 64 m tangent boundary. */
    private static double[] orthographicWide() {
        return new double[] {0.01D, 0, 0, 0, 0, 0.01D, 0, 0, 0, 0, -2.0D / 100.0D, 0,
                0, 0, -101.0D / 100.0D, 1};
    }

    private static double[] farProjection() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -10_001.0D / 9_999.0D, -1,
                0, 0, -20_000.0D / 9_999.0D, 0};
    }

    /**
     * Perspective near=0.05, far=10,000: float-upload residual remains admissible, while at
     * 300 m one D24 step is still about 0.11 m along the view ray, far above the 1/64 m tau cap.
     */
    private static double[] tinyNearProjection() {
        double near = 0.05D;
        double far = 10_000.0D;
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -(far + near) / (far - near), -1,
                0, 0, -(2.0D * far * near) / (far - near), 0};
    }

    /** Relative pivoting accepts this finite matrix, but its inverse X entry is 1e40 and cannot upload as float. */
    private static double[] floatOverflowingInverseModelView() {
        return new double[] {1.0E-40D, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -5, 1};
    }

    private static double[] floatMatrix(double[] matrix) {
        double[] result = new double[matrix.length];
        for (int index = 0; index < matrix.length; index++) result[index] = (float) matrix[index];
        return result;
    }

    private static double d24(double depth) {
        return Math.rint(depth * 16_777_215.0D) / 16_777_215.0D;
    }
}
