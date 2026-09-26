package wi.unigrav.core.collision;

import java.nio.file.Path;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCast3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d;
import org.junit.jupiter.api.Test;

import static dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same consumer suite runs against the pinned MCP jar and the remapped runtime jar. */
class ChbCapsuleContractTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final Capsule BODY = new Capsule(new Segment3d(new Vec3d(0, -0.75, 0), new Vec3d(0, 0.75, 0)), 0.25);

    @Test
    void publicEntryAndGeometryActuallyLoadFromTheSelectedArtifact() throws Exception {
        Path expected = Path.of(System.getProperty("unigrav.test.chbArtifact")).toRealPath();
        // Class origin inspection is an artifact-verification concern, not gameplay reflection.
        for (Class<?> type : new Class<?>[]{CapsuleAabbCast3d.class, CapsuleAabbCastResult3d.class, Capsule.class, Aabb.class}) {
            assertEquals(expected, Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        }
        assertEquals(1, CapsuleAabbCast3d.contractRevision());
        assertDoesNotThrow(ChbDependency::requireSupportedContract);
    }

    @Test
    void clearMovementCoversTheWholeRequestedPath() {
        var box = box(3, -1, -1, 4, 1, 1);
        var result = CapsuleAabbCast3d.cast(BODY, new Vec3d(1, 0, 0), box);
        assertEquals(CLEAR_SEPARATED, result.kind());
        assertEquals(1, result.safePrefixEnd().orElseThrow());
        assertTrue(result.bandBracket().isEmpty());
        assertTrue(result.reason().isEmpty());
    }

    @Test
    void normalTwoMetreMotionCannotSkipAThinWallHitByTheCylindricalMiddle() {
        var wall = box(1, -0.125, -0.5, 1.125, 0.125, 0.5);
        assertTrue(BODY.bounds().max().x() < wall.min().x());
        assertTrue(BODY.bounds().min().x() + 2 > wall.max().x());
        assertTrue(BODY.centerline().start().y() + BODY.radius() < wall.min().y());
        assertTrue(BODY.centerline().end().y() - BODY.radius() > wall.max().y());
        var result = CapsuleAabbCast3d.cast(BODY, new Vec3d(2, 0, 0), wall);
        assertBandBeforePhysicalContact(result, 0.375);
    }

    @Test
    void arbitraryFixedBodyAxisUsesTheRealCapsuleQuery() {
        var diagonal = new Capsule(new Segment3d(new Vec3d(-0.5, -0.5, 0), new Vec3d(0.5, 0.5, 0)), 0.25);
        var result = CapsuleAabbCast3d.cast(diagonal, new Vec3d(0, 0, 2), box(-0.125, -0.125, 1, 0.125, 0.125, 1.125));
        assertBandBeforePhysicalContact(result, 0.375);
    }

    @Test
    void initialContactKindsDoNotFabricateSeparatedPrefixes() {
        var touching = new Capsule(new Segment3d(new Vec3d(0.75, -0.5, 0), new Vec3d(0.75, 0.5, 0)), 0.25);
        var wall = box(1, -2, -2, 2, 2, 2);
        Vec3d[] requests = {new Vec3d(0.25, 0, 0), new Vec3d(-0.25, 0, 0), new Vec3d(0, 0.25, 0), ZERO};
        var kinds = new CapsuleAabbCastResult3d.Kind[]{HIT_INITIAL_INWARD, CLEAR_INITIAL_OUTWARD, CLEAR_INITIAL_TANGENT, CLEAR_INITIAL_TANGENT};
        for (int index = 0; index < requests.length; index++) {
            var result = CapsuleAabbCast3d.cast(touching, requests[index], wall);
            assertEquals(kinds[index], result.kind());
            assertTrue(result.safePrefixEnd().isEmpty());
            assertTrue(result.bandBracket().isEmpty());
        }
    }

    @Test
    void overlapAndUnsupportedRequestsRemainExplicitNonClearResults() {
        var overlap = CapsuleAabbCast3d.cast(BODY, ZERO, box(-1, -1, -1, 1, 1, 1));
        assertEquals(INITIAL_OVERLAP, overlap.kind());
        assertTrue(overlap.safePrefixEnd().isEmpty());
        assertTrue(overlap.normalEstimate().isEmpty());
        var unsupported = CapsuleAabbCast3d.cast(BODY, new Vec3d(65, 0, 0), box(1, -1, -1, 2, 1, 1));
        assertEquals(UNRESOLVED, unsupported.kind());
        assertEquals(CapsuleAabbCastResult3d.UnresolvedReason.UNSUPPORTED_DOMAIN, unsupported.reason().orElseThrow());
        assertTrue(unsupported.safePrefixEnd().isEmpty());
        assertTrue(unsupported.normalEstimate().isEmpty());
    }

    @Test
    void invalidCallsFailRatherThanReturningAnUnobstructedMove() {
        assertThrows(NullPointerException.class, () -> CapsuleAabbCast3d.cast(BODY, null, box(1, -1, -1, 2, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Vec3d(Double.NaN, 0, 0));
    }

    private static void assertBandBeforePhysicalContact(CapsuleAabbCastResult3d result, double physicalContact) {
        assertEquals(HIT_AFTER_CLEAR, result.kind());
        var bracket = result.bandBracket().orElseThrow();
        assertEquals(bracket.lastOutsideBandFraction(), result.safePrefixEnd().orElseThrow());
        assertTrue(0 < bracket.lastOutsideBandFraction());
        assertTrue(bracket.lastOutsideBandFraction() < bracket.insideContactBandFraction());
        assertTrue(bracket.insideContactBandFraction() <= physicalContact);
        assertEquals(physicalContact, bracket.insideContactBandFraction(), 1e-7);
        assertTrue(result.work().evaluationCount() <= 73);
    }

    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }
}
