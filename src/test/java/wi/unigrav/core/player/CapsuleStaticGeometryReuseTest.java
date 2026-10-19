package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleStaticGeometryReuseTest {
    @TempDir Path directory;
    private static final QueryLimits LIMITS = new QueryLimits(8192, 100000, 256, 128, 9344, 150000, 128);
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final Vec3d START = new Vec3d(-2, .5, .5), END = new Vec3d(-2, .75, .5);
    private static final Aabb BOX = new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1));
    private static Capsule capsule() { return new Capsule(new Segment3d(START, END), .25); }
    private InstalledFreeEndpointTest.Scene owner(String name) throws Exception {
        var fixture = new InstalledFreeEndpointTest(); fixture.directory = directory;
        return fixture.scene(false, name);
    }
    private static Vec3d changed(Vec3d p, int axis) {
        return new Vec3d(axis == 0 ? Math.nextUp(p.x()) : p.x(),
                axis == 1 ? Math.nextUp(p.y()) : p.y(), axis == 2 ? Math.nextUp(p.z()) : p.z());
    }
    @Test void equalRawTupleReusesTheActualProofAndWorkOnlyOnMissButEveryHitConsumesCall() throws Exception {
        try (var scene = owner("same-geometry")) {
            var budget = new CapsuleContactQueryBudget(LIMITS, 8);
            var first = budget.queryReusingGeometry(scene.tx(), capsule(), BOX, ACCURACY);
            assertEquals(Relation.SEPARATED, first.relation());
            var spent = budget.work(); assertTrue(spent.exactOps() > 0);
            var second = budget.queryReusingGeometry(scene.tx(), capsule(),
                    new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1)),
                    new ContactAccuracy(1e-6, 1e-6, 1e-5));
            assertSame(first, second, "same real immutable result, not a manufactured zero-work receipt");
            assertEquals(spent, budget.work()); assertEquals(2, budget.calls());
            var uncached = budget.query(capsule(), BOX, ACCURACY);
            assertNotSame(first, uncached, "ordinary query retains its actual-evaluation contract");
            assertEquals(spent.exactOps() + uncached.work().exactOps(), budget.work().exactOps());
        }
    }
    @Test void everyCapsuleBoxAndAccuracyComponentIsPartOfTheExactRawKey() throws Exception {
        try (var scene = owner("each-component")) {
            for (int component = 0; component < 16; component++) {
                var budget = new CapsuleContactQueryBudget(LIMITS, 8);
                var first = budget.queryReusingGeometry(scene.tx(), capsule(), BOX, ACCURACY);
                var cap = capsule(); var box = BOX; var accuracy = ACCURACY;
                if (component < 3) cap = new Capsule(new Segment3d(changed(START, component), END), .25);
                else if (component < 6) cap = new Capsule(new Segment3d(START, changed(END, component - 3)), .25);
                else if (component == 6) cap = new Capsule(new Segment3d(START, END), Math.nextUp(.25));
                else if (component < 10) box = new Aabb(changed(BOX.min(), component - 7), BOX.max());
                else if (component < 13) box = new Aabb(BOX.min(), changed(BOX.max(), component - 10));
                else accuracy = new ContactAccuracy(component == 13 ? Math.nextUp(1e-6) : 1e-6,
                        component == 14 ? Math.nextUp(1e-6) : 1e-6, component == 15 ? Math.nextUp(1e-5) : 1e-5);
                var spent = budget.work();
                var next = budget.queryReusingGeometry(scene.tx(), cap, box, accuracy);
                assertNotSame(first, next, "changed raw component=" + component);
                assertTrue(budget.work().exactOps() > spent.exactOps());
                assertEquals(spent.exactOps() + next.work().exactOps(), budget.work().exactOps());
            }
        }
    }
    @Test void finalAllowedHitLatchesTerminalAndAlreadyTerminalCannotReuse() throws Exception {
        try (var scene = owner("terminal-hit")) {
            var budget = new CapsuleContactQueryBudget(LIMITS, 2);
            var proof = budget.queryReusingGeometry(scene.tx(), capsule(), BOX, ACCURACY);
            var spent = budget.work();
            assertSame(proof, budget.queryReusingGeometry(scene.tx(), capsule(), BOX, ACCURACY));
            assertTrue(budget.terminal()); assertEquals(spent, budget.work());
            assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                    () -> budget.queryReusingGeometry(scene.tx(), capsule(), BOX, ACCURACY));
            assertEquals(3, budget.calls()); assertEquals(spent, budget.work());
        }
    }
    @Test void actuallySpentExactWorkCannotBeBypassedByAnExistingCachedProof() throws Exception {
        try (var scene = owner("spent-exact-work")) {
            var measured = new CapsuleContactQueryBudget(LIMITS, 8)
                    .query(capsule(), BOX, ACCURACY);
            var exactCap = new QueryLimits(8192, measured.work().exactOps(), 256, 128, 9344, 150000, 128);
            var budget = new CapsuleContactQueryBudget(exactCap, 8);
            var first = budget.queryReusingGeometry(scene.tx(), capsule(), BOX, ACCURACY);
            assertEquals(Relation.SEPARATED, first.relation()); assertTrue(budget.terminal());
            var spent = budget.work();
            assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                    () -> budget.queryReusingGeometry(scene.tx(), capsule(), BOX, ACCURACY));
            assertEquals(spent, budget.work()); assertEquals(2, budget.calls());
        }
    }
    @Test void unresolvedResultIsNeverCachedAndEachAttemptReallyEntersChb() throws Exception {
        try (var scene = owner("unresolved-not-cached")) {
            var budget = new CapsuleContactQueryBudget(LIMITS, 8);
            var unsupported = new Capsule(new Segment3d(START, END), 65);
            var first = budget.queryReusingGeometry(scene.tx(), unsupported, BOX, ACCURACY);
            assertEquals(Relation.UNRESOLVED, first.relation());
            assertEquals(Reason.UNSUPPORTED_DOMAIN, first.reason().orElseThrow()); assertFalse(budget.terminal());
            var second = budget.queryReusingGeometry(scene.tx(), unsupported, BOX, ACCURACY);
            assertNotSame(first, second); assertEquals(2, budget.calls());
            assertEquals(first.work().exactOps() + second.work().exactOps(), budget.work().exactOps());
        }
    }
    @Test void sharedBudgetDoesNotCarryProofAcrossTransactionIdentityOrPastCurrentFailure() throws Exception {
        var budget = new CapsuleContactQueryBudget(LIMITS, 8);
        ContactResult first;
        FreeOwnerTransaction retired;
        try (var firstOwner = owner("first-owner")) {
            retired = firstOwner.tx();
            first = budget.queryReusingGeometry(retired, capsule(), BOX, ACCURACY);
        }
        // The fixture owns dimension zero: close it before opening the next actual world.
        // Keep the same budget/proof so the distinct transaction still must perform a real miss.
        try (var secondOwner = owner("second-owner")) {
            var second = budget.queryReusingGeometry(secondOwner.tx(), capsule(), BOX, ACCURACY);
            assertNotSame(first, second);
            assertEquals(first.work().exactOps() + second.work().exactOps(), budget.work().exactOps());
            long calls = budget.calls(); var spent = budget.work();
            assertThrows(IllegalStateException.class,
                    () -> budget.queryReusingGeometry(retired, capsule(), BOX, ACCURACY));
            secondOwner.current().set(false);
            assertThrows(IllegalStateException.class,
                    () -> budget.queryReusingGeometry(secondOwner.tx(), capsule(), BOX, ACCURACY));
            assertEquals(calls, budget.calls()); assertEquals(spent, budget.work());
        }
    }
}
