package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Required RN-subpath success and exact-product boundary; old CHB is expected RED until its bridge repair. */
class FreeNearFootRnConsumerTest {
    @TempDir Path directory;
    private static final Vec3d CENTER = new Vec3d(9.375, 68.5, 8.5);
    private static final Vec3d DELTA = new Vec3d(-.24999999999999994, -5.551115123125783E-17, 0);
    private static final Aabb BOX = new Aabb(new Vec3d(8, 68, 8), new Vec3d(9, 69, 9));
    private static final Vec3d UP = new Vec3d(1, 0, 0);

    @Test void xPosExactOriginalSubpathWithRnEndpointRequiresGenuineFullBridgeSuccess() throws Exception {
        try (var scene = owner("x-pos-rn-bridge")) {
            var work = scene.tx().numeric();
            Vec3d sub = DELTA.multiply(.5), at = CENTER.add(sub);
            assertEquals(new Vec3d(9.25, 68.5, 8.5), at);
            assertTrue(FreeNearFootDrive.exactSubpath(CENTER, DELTA, .5, sub, at, work));
            assertFalse(StoredDyadic.of(CENTER.x(), work).add(StoredDyadic.of(sub.x(), work), work)
                    .equals(StoredDyadic.of(at.x(), work)), "real X residual must exercise the removed UG premise");
            assertFalse(StoredDyadic.of(CENTER.y(), work).add(StoredDyadic.of(sub.y(), work), work)
                    .equals(StoredDyadic.of(at.y(), work)), "real Y residual must remain present too");
            var actual = scene.tx().queries().query(sphere(at), BOX, FreeOwnerTransaction.ACCURACY);
            assertEquals(Relation.TOUCHING, actual.relation());
            assertTrue(actual.witness().isPresent());
            assertEquals(CapsuleSupportSlope.SlopeEvidence.SUFFICIENT,
                    CapsuleSupportSlope.evaluate(actual.witness().orElseThrow(), UP, work));
            long calls = scene.tx().queries().calls();
            // This is the required product behavior, not an old-pin rejection baseline.
            // Old CHB may throw QueryUnavailable(CORRECTION_PATH_UNPROVED) here; keep
            // that genuine RED until a separately validated CHB bridge closes the gap.
            var proof = FreeNearFootDrive.proveCandidate(scene.tx(), sphere(CENTER), DELTA, .5, BOX, UP);
            assertNotNull(proof);
            var boundary = proof.boundary();
            assertEquals(calls + 2, scene.tx().queries().calls(), "actual contact and real refinement must both execute");
            assertTrue(boundary.kind() == BoundaryKind.NONPENETRATING_FULL
                    || boundary.kind() == BoundaryKind.SEPARATED_FULL, boundary::toString);
            assertTrue(boundary.prefix().orElseThrow().endFraction() >= 1);
            var installed = boundary.installation().orElseThrow();
            assertEquals(1, installed.fraction());
            assertEquals(at, installed.storedCenter());
            assertEquals(new Vec3d(0, 0, 0), installed.halfLine());
            assertEquals(sphere(at), installed.storedCapsule());
            assertEquals(Relation.TOUCHING, installed.storedContact().relation());
            assertTrue(installed.storedContact().witness().isPresent());
            assertEquals(CapsuleSupportSlope.SlopeEvidence.SUFFICIENT,
                    CapsuleSupportSlope.evaluate(installed.storedContact().witness().orElseThrow(), UP, work));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, scene.tx().installationStage());
            scene.tx().requireCurrent();
        }
    }

    @Test void nextFractionCannotForgeSubcastButMayProveItsOriginalParameter() throws Exception {
        try (var scene = owner("x-pos-inexact-product")) {
            double fraction = Math.nextUp(.5);
            Vec3d sub = DELTA.multiply(fraction), at = CENTER.add(sub);
            assertFalse(FreeNearFootDrive.exactSubpath(CENTER, DELTA, fraction, sub, at, scene.tx().numeric()));
            assertEquals(new Vec3d(9.25, 68.5, 8.5), at);
            long calls = scene.tx().queries().calls();
            // This does not authorize the rounded subcast: CHB certifies the original
            // parameter, whose exact positive margin remains outside this face.
            var proof = FreeNearFootDrive.proveCandidate(scene.tx(), sphere(CENTER), DELTA, fraction, BOX, UP);
            var original = proof.originalPrefix().orElseThrow().proof().orElseThrow();
            assertEquals(fraction, original.endFraction());
            assertSame(DELTA, original.originalDelta());
            assertEquals(fraction, original.installation().fraction());
            assertEquals(at, original.installation().storedCenter());
            assertEquals(Relation.TOUCHING, original.installation().storedContact().relation());
            assertEquals(calls + 2, scene.tx().queries().calls(), "actual contact plus original-prefix proof");
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, scene.tx().installationStage());
        }
    }

    private InstalledFreeEndpointTest.Scene owner(String name) throws Exception {
        var fixture = new InstalledFreeEndpointTest();
        fixture.directory = directory;
        return fixture.scene(false, name);
    }
    private static Capsule sphere(Vec3d center) { return new Capsule(new Segment3d(center, center), .25); }
}
